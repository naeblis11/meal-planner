package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class CombineParityTest {
    private fun line(json: JsonElement): Line {
        val parts = json.jsonArray
        return Line(parts[0].str()!!, parts[1].str(), parts[2].str())
    }

    @Test
    fun combineLines() {
        for (case in ParityFixtures.load("combine.json").jsonArray) {
            val rows = case.obj["rows"]!!.jsonArray.map(::line)
            val expected = case.obj["expected"]!!.jsonArray.map(::line)
            assertEquals("combine_lines($rows)", expected, Units.combineLines(rows))
        }
    }
}
