package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekTest {
    @Test
    fun weeksStartOnMonday() {
        val monday = LocalDate.of(2026, 9, 28)
        assertEquals(monday, Week.start(LocalDate.of(2026, 10, 1))) // a Thursday
        assertEquals(monday, Week.start(monday))
        assertEquals(monday, Week.start(LocalDate.of(2026, 10, 4))) // the Sunday
        assertEquals(LocalDate.of(2026, 10, 5), Week.start(LocalDate.of(2026, 10, 5)))
        assertEquals(7, Week.dates(monday).size)
        assertEquals(LocalDate.of(2026, 10, 4), Week.dates(monday).last())
    }

    @Test
    fun labelsReadLikeThePi() {
        assertEquals("Sep 28 \u2013 Oct 4", Week.label(LocalDate.of(2026, 9, 28)))
        assertEquals("Oct 5 \u2013 11", Week.label(LocalDate.of(2026, 10, 5)))
        assertEquals("Thursday, Oct 1", Week.dayLabel(LocalDate.of(2026, 10, 1)))
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), Week.SLOTS)
    }

    @Test
    fun aWeekCanSpanTheNewYear() {
        val monday = LocalDate.of(2026, 12, 28)
        assertEquals(monday, Week.start(LocalDate.of(2027, 1, 3))) // the Sunday, already in 2027
        assertEquals(LocalDate.of(2027, 1, 3), Week.dates(monday).last())
        assertEquals("Dec 28 \u2013 Jan 3", Week.label(monday))
        assertEquals(LocalDate.of(2027, 1, 4), Week.start(LocalDate.of(2027, 1, 4)))
    }
}
