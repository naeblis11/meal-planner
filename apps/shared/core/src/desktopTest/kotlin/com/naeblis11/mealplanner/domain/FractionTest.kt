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

    // Python 3.13: int() refuses more than 4300 digits, so Fraction() raises ValueError and parse_amount gives None.
    // Fraction("1" * 4300) and Fraction("." + "1" * 4300) parse; "1" * 4301, "1/" + "1" * 4301 and "0." + "1" * 4301 do not.
    @Test
    fun aDigitRunPastPythonsIntLimitIsUnparseable() {
        assertEquals(4300, Fraction.parse("1".repeat(4300))!!.numerator.toString().length)
        assertEquals(4300, Fraction.parse("." + "1".repeat(4300))!!.numerator.toString().length)
        assertNull(Fraction.parse("1".repeat(4301)))
        assertNull(Fraction.parse("1".repeat(5000)))
        assertNull(Fraction.parse("1/" + "1".repeat(4301)))
        assertNull(Fraction.parse("0." + "1".repeat(4301)))
        assertNull(Fraction.parse("1e" + "0".repeat(4301)))
    }

    // Python's \d in a str pattern is any Unicode digit and int() reads them all, so Fraction("\u0663") is 3. The
    // phone's ICU \d is the same; the desktop JVM's is ASCII only, hence Py.RE_DIGIT and Py.RE_SPACE in the pattern.
    @Test
    fun digitsAndSpacesFromOtherScriptsParseAsPythonReadsThem() {
        assertEquals(Fraction.of(3), Fraction.parse("\u0663"))
        assertEquals(Fraction.of(7, 4), Amounts.parseAmount("1 \u0663/\u0664"))
        assertEquals(Fraction.of(10), Fraction.parse("1e\u0661"))
        assertEquals(Fraction.of(1, 8), Fraction.parse("\u0660.\u0661\u0662\u0665"))
        assertEquals(Fraction.of(3), Fraction.parse("\u3000\u0663\u00A0"))
        // A Roman numeral or a superscript is a number, not a digit: Python's int() refuses them too.
        assertNull(Fraction.parse("\u2160"))
        assertNull(Fraction.parse("\u00B3"))
        // Digits of any script count toward the 4300-digit limit.
        assertNull(Fraction.parse("\u0661".repeat(4301)))
        assertEquals(4300, Fraction.parse("\u0661".repeat(4300))!!.numerator.toString().length)
    }

    @Test
    fun parseAmountStripsPythonsWhitespace() {
        // str.strip() takes NEL (U+0085) off; Kotlin's trim() does not, and "3\u0085" then split into "3" and "".
        assertEquals(Fraction.of(3), Amounts.parseAmount("3\u0085"))
        assertEquals(Fraction.of(3, 2), Amounts.parseAmount("\u0085 1 1/2\u0085"))
        assertNull(Amounts.parseAmount("\u0085"))
    }

    @Test
    fun anAmountWithAHugeDigitRunIsUnparseable() {
        assertNull(Amounts.parseAmount("1".repeat(5000)))
        assertNull(Amounts.parseAmount("1 1/" + "1".repeat(5000)))
        assertNull(Py.parseInt("1".repeat(5000)))
        assertEquals(4300, Py.parseInt("1".repeat(4300))!!.toString().length)
    }
}
