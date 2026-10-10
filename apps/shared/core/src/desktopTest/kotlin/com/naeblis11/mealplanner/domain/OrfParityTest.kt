package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OrfParityTest {
    @Test
    fun parseMatchesThePi() {
        for (case in ParityFixtures.load("orf.json").jsonArray) {
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
    fun cleanNoneTreatsTheNoneWordsAsMissing() {
        assertEquals(null, Orf.cleanNone("None"))
        assertEquals(null, Orf.cleanNone("none"))
        assertEquals(null, Orf.cleanNone(""))
        assertEquals("NONE", Orf.cleanNone("NONE"))
        assertEquals(0L, Orf.cleanNone(0L))
    }
}
