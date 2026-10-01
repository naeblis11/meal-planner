package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PantryDatesTest {
    @Test
    fun datesAreIsoOrBlank() {
        assertNull(PantryDates.parse(null))
        assertNull(PantryDates.parse("  "))
        assertEquals("2026-09-13", PantryDates.parse(" 2026-09-13 "))
        assertThrows(IllegalArgumentException::class.java) { PantryDates.parse("2026-02-30") }
        assertThrows(IllegalArgumentException::class.java) { PantryDates.parse("13/09/2026") }
    }

    @Test
    fun friendlyDatesReadLikeThePi() {
        assertEquals("Sep 13, 2026", PantryDates.friendly("2026-09-13"))
        assertEquals("someday", PantryDates.friendly("someday"))
        assertEquals("", PantryDates.friendly(null))
    }
}
