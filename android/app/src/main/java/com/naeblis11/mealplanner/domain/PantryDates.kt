package com.naeblis11.mealplanner.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** A pantry item's added-on date: pantry.parse_added_on and app.py's friendly_date. */
object PantryDates {
    /** The Pi's message for a date it can't read. */
    const val HINT = "Enter the date as YYYY-MM-DD, or leave it blank to clear it."

    /** ISO YYYY-MM-DD, or null for blank ("no date"); anything else throws IllegalArgumentException. */
    fun parse(text: String?): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            LocalDate.parse(trimmed).toString()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Not a valid date: '$trimmed'")
        }
    }

    /** "2026-09-13" -> "Sep 13, 2026"; anything unparseable comes back as it is, so a stray value is visible. */
    fun friendly(iso: String?): String = try {
        val day = LocalDate.parse(iso ?: "")
        "${Week.month(day)} ${day.dayOfMonth}, ${day.year}"
    } catch (e: DateTimeParseException) {
        iso ?: ""
    }
}
