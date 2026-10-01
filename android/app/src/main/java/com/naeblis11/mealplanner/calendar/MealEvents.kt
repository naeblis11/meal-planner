package com.naeblis11.mealplanner.calendar

import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One planned meal as a calendar event; CalendarContract fields come from it in AndroidCalendarGateway. */
data class MealEvent(
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    /** The zone id the times were computed in; written as EVENT_TIMEZONE. */
    val timeZone: String,
)

/** One ingredient line as the recipe has it (recipe_ingredient's name, amount, unit, section). */
data class EventIngredient(val name: String, val amount: String?, val unit: String?, val section: String?)

/** gcal.build_event and gcal.format_ingredients, minus the link (a phone recipe has no URL). */
object MealEvents {
    /** Timed, not all-day, so a glance at the day shows dinner in the evening (gcal.SLOT_TIMES). */
    val SLOT_TIMES: Map<String, LocalTime> = mapOf(
        "Breakfast" to LocalTime.of(7, 0),
        "Lunch" to LocalTime.of(12, 0),
        "Dinner" to LocalTime.of(18, 0),
    )
    /**
     * The description's closing line, on every event the app writes. The gateway only updates,
     * deletes or looks up an event whose description ends with it, so a stale event id (after
     * Calendar Storage is cleared, say) can never name one of the family's own events.
     */
    const val MARKER = "Added by Meal Planner."
    private val DEFAULT_TIME: LocalTime = LocalTime.NOON
    private val DURATION: Duration = Duration.ofHours(1)

    /** The event for [recipeName] in [slot] on [date], at the slot's clock time in [zone] on that date. */
    fun build(
        date: LocalDate,
        slot: String,
        recipeName: String,
        servings: String?,
        ingredients: List<EventIngredient>,
        zone: ZoneId,
    ): MealEvent {
        val start = ZonedDateTime.of(date, SLOT_TIMES[slot] ?: DEFAULT_TIME, zone)
        return MealEvent(
            title = "$slot: $recipeName",
            description = description(servings, ingredients),
            startMillis = start.toInstant().toEpochMilli(),
            endMillis = start.plus(DURATION).toInstant().toEpochMilli(),
            timeZone = zone.id,
        )
    }

    fun description(servings: String?, ingredients: List<EventIngredient>): String {
        val lines = mutableListOf<String>()
        if (!servings.isNullOrEmpty()) lines += "Servings: $servings"
        if (ingredients.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "Ingredients:"
            lines += formatIngredients(ingredients)
        }
        lines += ""
        lines += MARKER
        return lines.joinToString("\n")
    }

    /** "  - 2 cups flour", with a blank line and "Section:" where a new section starts. */
    fun formatIngredients(ingredients: List<EventIngredient>): List<String> {
        val lines = mutableListOf<String>()
        var section: String? = null
        for (row in ingredients) {
            if (!row.section.isNullOrEmpty() && row.section != section) {
                section = row.section
                lines += ""
                lines += "$section:"
            }
            val measure = listOf(row.amount?.trim().orEmpty(), row.unit?.trim().orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
            lines += if (measure.isNotEmpty()) "  - $measure ${row.name}".trimEnd() else "  - ${row.name}"
        }
        return lines
    }

    /** What decides "changed": SHA-256 of everything written. Not the Pi's hash; the two never meet. */
    fun hash(event: MealEvent): String {
        val text = listOf(event.title, event.description, event.startMillis.toString(), event.endMillis.toString(), event.timeZone)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
