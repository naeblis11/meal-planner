package com.naeblis11.mealplanner.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the family reads when they tap the event: gcal.build_event, without the link. */
class MealEventsTest {
    private val zone = ZoneId.of("America/Toronto")
    private val monday = LocalDate.of(2026, 9, 28)

    private fun row(name: String, amount: String?, unit: String?, section: String? = null) = EventIngredient(name, amount, unit, section)

    private fun localStart(event: MealEvent) = Instant.ofEpochMilli(event.startMillis).atZone(zone)

    @Test
    fun eachSlotLandsAtItsOwnTimeForAnHour() {
        for ((slot, hour) in listOf("Breakfast" to 7, "Lunch" to 12, "Dinner" to 18)) {
            val event = MealEvents.build(monday, slot, "X", null, emptyList(), zone)
            assertEquals(slot, LocalTime.of(hour, 0), localStart(event).toLocalTime())
            assertEquals(monday, localStart(event).toLocalDate())
            assertEquals(60 * 60 * 1000L, event.endMillis - event.startMillis)
            assertEquals("America/Toronto", event.timeZone)
        }
        val other = MealEvents.build(monday, "Snack", "X", null, emptyList(), zone)
        assertEquals(LocalTime.NOON, localStart(other).toLocalTime())
    }

    @Test
    fun theLocalTimeHoldsAcrossTheYear() {
        // Daylight saving moves the offset, never the clock time.
        for (day in listOf("2026-01-15", "2026-03-08", "2026-07-04", "2026-11-01", "2026-12-31")) {
            val event = MealEvents.build(LocalDate.parse(day), "Dinner", "X", null, emptyList(), zone)
            assertEquals(day, LocalTime.of(18, 0), localStart(event).toLocalTime())
        }
    }

    @Test
    fun theTitleIsSlotAndRecipe() {
        assertEquals("Dinner: Chicken Parmesan", MealEvents.build(monday, "Dinner", "Chicken Parmesan", null, emptyList(), zone).title)
    }

    @Test
    fun theDescriptionReadsLikeThePis() {
        val text = MealEvents.description(
            "6",
            listOf(row("flour", "2", "cups"), row("butter", "1/2", "cup", "Topping")),
        )
        assertEquals(
            "Servings: 6\n\nIngredients:\n  - 2 cups flour\n\nTopping:\n  - 1/2 cup butter\n\nAdded by Meal Planner.",
            text,
        )
    }

    @Test
    fun anIngredientWithNoAmountStillReadsProperly() {
        val text = MealEvents.description(null, listOf(row("salt to taste", null, null), row("pepper", " ", "")))
        assertTrue(text, text.contains("  - salt to taste\n"))
        assertTrue(text, text.contains("  - pepper\n"))
        assertFalse("no double space from a blank amount", text.contains("-  "))
        assertTrue(text, text.startsWith("Ingredients:\n"))
    }

    @Test
    fun noIngredientsMeansNoEmptyHeading() {
        assertEquals("Servings: 4\n\nAdded by Meal Planner.", MealEvents.description("4", emptyList()))
        // As on the Pi, the closing line keeps its blank line above it even when it is all there is.
        assertEquals("\nAdded by Meal Planner.", MealEvents.description(null, emptyList()))
        assertEquals("\nAdded by Meal Planner.", MealEvents.description("", emptyList()))
    }

    @Test
    fun aRepeatedSectionIsHeadedOnce() {
        val lines = MealEvents.formatIngredients(
            listOf(row("a", "1", "cup", "Sauce"), row("b", "2", "cups", "Sauce"), row("c", "3", "tbsp")),
        )
        assertEquals(listOf("", "Sauce:", "  - 1 cup a", "  - 2 cups b", "  - 3 tbsp c"), lines)
    }

    @Test
    fun theHashFollowsTheContent() {
        val event = MealEvents.build(monday, "Dinner", "Chili", "4", listOf(row("beans", "2", "cups")), zone)
        val same = MealEvents.build(monday, "Dinner", "Chili", "4", listOf(row("beans", "2", "cups")), zone)
        assertEquals(MealEvents.hash(event), MealEvents.hash(same))
        assertTrue(Regex("[0-9a-f]{64}").matches(MealEvents.hash(event)))
        assertNotEquals(MealEvents.hash(event), MealEvents.hash(MealEvents.build(monday, "Dinner", "Chili", "8", listOf(row("beans", "2", "cups")), zone)))
        assertNotEquals(MealEvents.hash(event), MealEvents.hash(MealEvents.build(monday, "Dinner", "Chili", "4", listOf(row("beans", "2", "cups")), ZoneId.of("Europe/London"))))
    }
}
