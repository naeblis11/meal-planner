package com.naeblis11.mealplanner.calendar

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** gcal.build_event for the Calendar API: the meal's event, its JSON body, and the hash that says "changed". */
object GoogleEvents {
    // gcal._slot_window's isoformat(): seconds always written, and the offset that date has in the zone, a zero one
    // as "+00:00" (xxx), never "Z" (XXX).
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    /** The event for [recipeName] in [slot] on [date]: an hour at the slot's clock time in [zone], the phone's description. */
    fun build(
        date: LocalDate,
        slot: String,
        recipeName: String,
        servings: String?,
        ingredients: List<EventIngredient>,
        zone: ZoneId,
    ): GoogleEvent {
        val start = ZonedDateTime.of(date, MealEvents.SLOT_TIMES[slot] ?: LocalTime.NOON, zone)
        return GoogleEvent(
            summary = "$slot: $recipeName",
            description = MealEvents.description(servings, ingredients),
            start = STAMP.format(start),
            end = STAMP.format(start.plusHours(1)),
        )
    }

    /**
     * The body Google is sent: [id] only on an insert. Free, not busy, so nobody looks unavailable at dinner time; and
     * confirmed, so an update also brings back an event deleted by hand.
     */
    fun json(event: GoogleEvent, id: String? = null): String = buildJsonObject {
        if (id != null) put("id", id)
        put("summary", event.summary)
        put("description", event.description)
        putJsonObject("start") { put("dateTime", event.start) }
        putJsonObject("end") { put("dateTime", event.end) }
        put("transparency", "transparent")
        put("status", "confirmed")
    }.toString()

    /** SHA-256 (hex) of everything written; an unchanged meal hashes the same and is not sent again. */
    fun hash(event: GoogleEvent): String =
        MessageDigest.getInstance("SHA-256").digest(json(event).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
