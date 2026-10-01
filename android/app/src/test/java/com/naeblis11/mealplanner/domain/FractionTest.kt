package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FractionTest {
    @Test
    fun keepsLowestTermsWithAPositiveDenominator() {
        assertEquals("5/2", Fraction.of(10, 4).toString())
        assertEquals("-1/2", Fraction.of(1, -2).toString())
        assertEquals("0/1", Fraction.of(0, 7).toString())
    }

    @Test
    fun arithmeticIsExact() {
        assertEquals(Fraction.of(5, 6), Fraction.of(1, 2) + Fraction.of(1, 3))
        assertEquals(Fraction.of(1, 6), Fraction.of(1, 2) * Fraction.of(1, 3))
        assertEquals(Fraction.of(3, 2), Fraction.of(1, 2) / Fraction.of(1, 3))
        assertTrue(Fraction.of(1, 3) < Fraction.of(1, 2))
    }

    @Test
    fun parseFollowsPythonsFractionConstructor() {
        assertEquals(Fraction.of(5, 2), Fraction.parse("10/4"))
        assertEquals(Fraction.of(1, 8), Fraction.parse("0.125"))
        assertEquals(Fraction.of(100), Fraction.parse("1e2"))
        assertNull(Fraction.parse("1/0"))
        assertNull(Fraction.parse("abc"))
        assertNull(Fraction.parse("."))
    }
}
