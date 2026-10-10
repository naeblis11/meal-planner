package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParityFixturesTest {
    @Test
    fun loadsTheSharedFixtures() {
        val aisles = ParityFixtures.load("aisles.json").jsonArray
        assertTrue("aisles.json should have cases", aisles.isNotEmpty())
    }

    @Test
    fun anEmptyOrMissingListOfCasesFails() {
        // Every parity test loops over its cases, so an emptied fixture must fail, never pass vacuously.
        assertThrows(AssertionError::class.java) { JsonObject(mapOf("x" to JsonArray(emptyList()))).cases("x") }
        assertThrows(AssertionError::class.java) { JsonObject(emptyMap()).cases("x") }
        assertEquals(1, JsonObject(mapOf("x" to JsonArray(listOf(JsonPrimitive(1))))).cases("x").size)
        assertTrue(ParityFixtures.cases("aisles.json").isNotEmpty())
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
