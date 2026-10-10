package com.naeblis11.mealplanner.plan

import com.naeblis11.mealplanner.data.PlannedMealRow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekViewsTest {
    @Test
    fun sevenDaysOfThreeSlotsWithTheMealsInPlace() {
        val monday = LocalDate.of(2026, 9, 28)
        val lunch = PlannedMealRow("2026-09-29", "Lunch", 4, null, "Soup", null)
        val week = WeekViews.build(monday, listOf(lunch), today = LocalDate.of(2026, 10, 1))

        assertEquals("Sep 28 \u2013 Oct 4", week.label)
        assertEquals(7, week.days.size)
        assertEquals("Monday, Sep 28", week.days[0].label)
        assertEquals(listOf(false, false, false, true, false, false, false), week.days.map { it.isToday })
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), week.days[1].slots.map { it.slot })
        assertEquals(lunch, week.days[1].slots[1].meal)
        assertNull(week.days[1].slots[2].meal)
    }
}
