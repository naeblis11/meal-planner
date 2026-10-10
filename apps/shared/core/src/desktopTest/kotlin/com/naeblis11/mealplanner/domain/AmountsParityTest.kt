package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class AmountsParityTest {
    private val cases = ParityFixtures.load("amounts.json").obj

    @Test
    fun parseAmount() {
        for (case in cases["parse_amount"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()
            assertEquals("parse_amount($text)", case.obj["expected"]!!.str(),
                Amounts.parseAmount(text)?.toString())
        }
    }

    @Test
    fun formatAmount() {
        for (case in cases["format_amount"]!!.jsonArray) {
            val value = case.obj["value"]!!.str()!!
            assertEquals("format_amount($value)", case.obj["expected"]!!.str(),
                Amounts.formatAmount(Fraction.parse(value)!!))
        }
    }

    @Test
    fun servingsRatio() {
        for (case in cases["servings_ratio"]!!.jsonArray) {
            val planned = case.obj["planned"]!!.str()
            val base = case.obj["base"]!!.str()
            assertEquals("servings_ratio($planned, $base)", case.obj["expected"]!!.str(),
                Amounts.servingsRatio(planned, base).toString())
        }
    }

    @Test
    fun scaleAmountText() {
        for (case in cases["scale_amount_text"]!!.jsonArray) {
            val amount = case.obj["amount"]!!.str()
            val ratio = case.obj["ratio"]!!.str()!!
            assertEquals("scale_amount_text($amount, $ratio)", case.obj["expected"]!!.str(),
                Amounts.scaleAmountText(amount, Fraction.parse(ratio)!!))
        }
    }
}
