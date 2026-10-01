package com.naeblis11.mealplanner.calendar

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarMessagesTest {
    private val lunch = SendFailure(LocalDate.of(2026, 9, 29), "Lunch", "refused")
    private val dinner = SendFailure(LocalDate.of(2026, 9, 30), "Dinner", "refused")

    @Test
    fun theSummaryReadsPlainly() {
        assertEquals("Calendar updated: 2 added, 1 removed.", CalendarMessages.summary(SendOutcome.Sent(2, 0, 1, 3, emptyList())))
        assertEquals("Calendar updated: 1 updated.", CalendarMessages.summary(SendOutcome.Sent(0, 1, 0, 0, emptyList())))
        assertEquals("Your calendar was already up to date.", CalendarMessages.summary(SendOutcome.Sent(0, 0, 0, 3, emptyList())))
        // Nothing changed and something failed: the failure banner says it all.
        assertEquals(null, CalendarMessages.summary(SendOutcome.Sent(0, 0, 0, 0, listOf(lunch))))
    }

    @Test
    fun failuresNameEachMeal() {
        assertEquals(
            "1 meal could not be sent: Lunch on Tuesday, Sep 29. Send the week again to try it again.",
            CalendarMessages.failures(listOf(lunch)),
        )
        assertEquals(
            "2 meals could not be sent: Lunch on Tuesday, Sep 29; Dinner on Wednesday, Sep 30. Send the week again to try them again.",
            CalendarMessages.failures(listOf(lunch, dinner)),
        )
    }
}
