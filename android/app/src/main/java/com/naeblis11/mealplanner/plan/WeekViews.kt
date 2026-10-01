package com.naeblis11.mealplanner.plan

import com.naeblis11.mealplanner.data.PlannedMealRow
import com.naeblis11.mealplanner.domain.Week
import java.time.LocalDate

/** One slot of one day; [meal] is null when nothing is planned. */
data class SlotView(val slot: String, val meal: PlannedMealRow?)

data class DayView(val date: LocalDate, val label: String, val isToday: Boolean, val slots: List<SlotView>)

data class WeekView(val start: LocalDate, val label: String, val days: List<DayView>)

/** The week grid of app.py `calendar_view`: seven days, Breakfast/Lunch/Dinner each. */
object WeekViews {
    fun build(start: LocalDate, meals: List<PlannedMealRow>, today: LocalDate): WeekView {
        val bySlot = meals.associateBy { it.date to it.slot }
        return WeekView(
            start = start,
            label = Week.label(start),
            days = Week.dates(start).map { day ->
                DayView(day, Week.dayLabel(day), day == today, Week.SLOTS.map { slot -> SlotView(slot, bySlot[day.toString() to slot]) })
            },
        )
    }
}
