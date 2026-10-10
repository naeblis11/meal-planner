package com.naeblis11.mealplanner.calendar

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** gcal.build_event's shape: a timed hour at the slot's clock time, with that date's UTC offset, marked free. */
class GoogleEventsTest {
    private val zone = ZoneId.of("America/Toronto")

    @Test
    fun eachSlotLandsAtItsOwnTimeWithThatDaysOffset() {
        val winter = GoogleEvents.build(LocalDate.of(2026, 1, 5), "Breakfast", "Oats", null, emptyList(), zone)
        assertEquals("Breakfast: Oats", winter.summary)
        assertEquals("2026-01-05T07:00:00-05:00", winter.start)
        assertEquals("2026-01-05T08:00:00-05:00", winter.end)
        val summer = GoogleEvents.build(LocalDate.of(2026, 7, 6), "Lunch", "Salad", null, emptyList(), zone)
        assertEquals("2026-07-06T12:00:00-04:00", summer.start)
        assertEquals("2026-10-05T18:00:00-04:00", GoogleEvents.build(LocalDate.of(2026, 10, 5), "Dinner", "Soup", null, emptyList(), zone).start)
        assertEquals("2026-10-05T12:00:00-04:00", GoogleEvents.build(LocalDate.of(2026, 10, 5), "Snack", "Fruit", null, emptyList(), zone).start)
    }

    @Test
    fun aZeroOffsetIsWrittenAsPythonsIsoformatWritesItNotAsZ() {
        val utc = GoogleEvents.build(LocalDate.of(2026, 10, 5), "Dinner", "Soup", null, emptyList(), ZoneId.of("UTC"))
        assertEquals("2026-10-05T18:00:00+00:00", utc.start)
        assertEquals("2026-10-05T19:00:00+00:00", utc.end)
        // London in winter is UTC too; in summer it is an hour ahead.
        assertEquals("2026-01-05T18:00:00+00:00", GoogleEvents.build(LocalDate.of(2026, 1, 5), "Dinner", "Soup", null, emptyList(), ZoneId.of("Europe/London")).start)
        assertEquals("2026-07-06T18:00:00+01:00", GoogleEvents.build(LocalDate.of(2026, 7, 6), "Dinner", "Soup", null, emptyList(), ZoneId.of("Europe/London")).start)
    }

    @Test
    fun theBodyIsFreeConfirmedAndNamesItsIdOnlyOnInsert() {
        val event = GoogleEvent("Dinner: Soup", "Added by Meal Planner.", "2026-10-05T18:00:00-04:00", "2026-10-05T19:00:00-04:00")
        assertEquals(
            "{\"id\":\"mpabc\",\"summary\":\"Dinner: Soup\",\"description\":\"Added by Meal Planner.\"," +
                "\"start\":{\"dateTime\":\"2026-10-05T18:00:00-04:00\"},\"end\":{\"dateTime\":\"2026-10-05T19:00:00-04:00\"}," +
                "\"transparency\":\"transparent\",\"status\":\"confirmed\"}",
            GoogleEvents.json(event, "mpabc"),
        )
        assertEquals(GoogleEvents.json(event, "mpabc").replace("\"id\":\"mpabc\",", ""), GoogleEvents.json(event))
    }

    @Test
    fun theHashFollowsWhatIsWrittenAndNothingElse() {
        val soup = { servings: String? -> GoogleEvents.build(LocalDate.of(2026, 10, 5), "Dinner", "Soup", servings, emptyList(), zone) }
        assertEquals(GoogleEvents.hash(soup("4")), GoogleEvents.hash(soup("4")))
        assertEquals(64, GoogleEvents.hash(soup("4")).length)
        assertNotEquals(GoogleEvents.hash(soup("4")), GoogleEvents.hash(soup("6")))
    }
}
