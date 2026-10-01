package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityFixturesTest {
    @Test
    fun loadsTheSharedFixtures() {
        val aisles = ParityFixtures.load("aisles.json").jsonArray
        assertTrue("aisles.json should have cases", aisles.isNotEmpty())
    }

    @Test
    fun jsonNullReadsAsKotlinNull() {
        val parse = ParityFixtures.load("amounts.json").obj["parse_amount"]!!.jsonArray
        val blank = parse.first { it.obj["text"]!!.str() == "" }
        assertNull(blank.obj["expected"]!!.str())
        val two = parse.first { it.obj["text"]!!.str() == "2" }
        assertEquals("2/1", two.obj["expected"]!!.str())
    }
}
