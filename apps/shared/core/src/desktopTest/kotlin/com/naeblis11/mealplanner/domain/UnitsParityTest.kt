package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsParityTest {
    private val cases = ParityFixtures.load("amounts.json").obj

    @Test
    fun normalizeUnit() {
        for (case in cases["normalize_unit"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()
            assertEquals("normalize_unit($text)", case.obj["expected"]!!.str(), Units.normalizeUnit(text))
        }
    }

    @Test
    fun isKnownUnitWord() {
        for (case in cases["is_known_unit_word"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()!!
            assertEquals("is_known_unit_word($text)", case.obj["expected"]!!.jsonPrimitive.boolean,
                Units.isKnownUnitWord(text))
        }
    }

    @Test
    fun toImperial() {
        for (case in cases["to_imperial"]!!.jsonArray) {
            val amount = case.obj["amount"]!!.str()
            val unit = case.obj["unit"]!!.str()
            val expected = case.obj["expected"]!!.jsonArray
            assertEquals("to_imperial($amount, $unit)", expected[0].str() to expected[1].str(),
                Units.toImperial(amount, unit))
        }
    }

    @Test
    fun bestUnitFallsBackToTheSmallestUnitBelowOne() {
        assertEquals("tsp" to Fraction.of(1, 2), Units.bestUnit(Units.Category.VOLUME, Fraction.of(1, 2)))
        assertEquals("lb" to Fraction.of(2), Units.bestUnit(Units.Category.WEIGHT, Fraction.of(32)))
    }
}
