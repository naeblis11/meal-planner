package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The meal plan's week, Monday to Sunday, three meals a day: meal_calendar.py's date math and app.py's labels. */
object Week {
    val SLOTS: List<String> = listOf("Breakfast", "Lunch", "Dinner")

    /** The Monday on or before [day] (meal_calendar.get_week_start). */
    fun start(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    fun dates(start: LocalDate): List<LocalDate> = (0L..6L).map { start.plusDays(it) }

    /** calendar_view's week_label: "Sep 28 \u2013 Oct 4", or "Oct 5 \u2013 11" within one month. */
    fun label(start: LocalDate): String {
        val end = start.plusDays(6)
        return if (start.month == end.month) {
            "${month(start)} ${start.dayOfMonth} \u2013 ${end.dayOfMonth}"
        } else {
            "${month(start)} ${start.dayOfMonth} \u2013 ${month(end)} ${end.dayOfMonth}"
        }
    }

    /** "Thursday, Oct 1". */
    fun dayLabel(day: LocalDate): String =
        "${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, ${month(day)} ${day.dayOfMonth}"

    /** "Sep": Python's %b in the C locale. */
    fun month(day: LocalDate): String = day.month.getDisplayName(TextStyle.SHORT, Locale.US)
}
