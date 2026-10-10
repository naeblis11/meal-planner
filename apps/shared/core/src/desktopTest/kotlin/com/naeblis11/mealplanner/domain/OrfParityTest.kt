package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OrfParityTest {
    @Test
    fun parseMatchesThePi() {
        for (case in ParityFixtures.cases("orf.json")) {
            val name = case.obj["name"]!!.str()
            val yaml = case.obj["yaml"]!!.str()!!
            val error = case.obj["error"]?.str()
            if (error == null) {
                assertEquals(name, case.obj["expected"], JsonTree.toJson(Orf.parse(yaml)))
            } else {
                try {
                    Orf.parse(yaml)
                    fail("$name should not parse")
                } catch (e: RecipeFormatException) {
                    if (error != "*") assertEquals(name, error, e.message)
                }
            }
        }
    }

    @Test
    fun aRatingInAnotherScriptsDigitsReadsAsPythonsIntDoes() {
        // int("\u0663") is 3 in Python, so normalize_rating gives 3; Py.parseInt reads the same digits.
        assertEquals(3, Orf.normalizeRating("\u0663"))
        assertEquals(5, Orf.normalizeRating(" \u0665 "))
        assertEquals(null, Orf.normalizeRating("\u0669"))
        assertEquals(java.math.BigInteger.valueOf(12), Py.parseInt("\u0661\u0662"))
        assertEquals(null, Py.parseInt("\u00B3"))
    }

    @Test
    fun cleanNoneTreatsTheNoneWordsAsMissing() {
        assertEquals(null, Orf.cleanNone("None"))
        assertEquals(null, Orf.cleanNone("none"))
        assertEquals(null, Orf.cleanNone(""))
        assertEquals("NONE", Orf.cleanNone("NONE"))
        assertEquals(0L, Orf.cleanNone(0L))
    }
}
