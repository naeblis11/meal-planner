package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PyCompatParityTest {
    private val cases = ParityFixtures.load("pycompat.json").obj

    @Test
    fun title() {
        for (case in cases["title"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()!!
            assertEquals("title($text)", case.obj["expected"]!!.str(), Py.title(text))
        }
    }

    @Test
    fun splitLines() {
        for (case in cases["splitlines"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()!!
            assertEquals("splitlines(${text.toList()})", case.obj["expected"]!!.jsonArray.map { it.str() }, Py.splitLines(text))
        }
    }

    @Test
    fun split() {
        for (case in cases["split"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()!!
            val maxSplit = case.obj["maxsplit"]!!.jsonPrimitive.int
            assertEquals("split($text, $maxSplit)", case.obj["expected"]!!.jsonArray.map { it.str() }, Py.split(text, maxSplit))
        }
    }

    @Test
    fun strip() {
        for (case in cases["strip"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()!!
            assertEquals("strip(${text.toList()})", case.obj["expected"]!!.str(), Py.strip(text))
        }
    }

    @Test
    fun str() {
        for (case in cases["str"]!!.jsonArray) {
            val value = JsonTree.fromJson(case.obj["value"]!!)
            assertEquals("str($value)", case.obj["expected"]!!.str(), Py.str(value))
        }
    }

    @Test
    fun cp1252() {
        val case = cases["cp1252"]!!.obj
        val bytes = case["bytes"]!!.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
        assertEquals(case["expected"]!!.str(), Py.decodeCp1252(bytes))
    }
}
