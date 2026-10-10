package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingMergeParityTest {
    private fun line(json: JsonElement): Line {
        val parts = json.jsonArray
        return Line(parts[0].str()!!, parts[1].str(), parts[2].str())
    }

    /** A row as the fixtures record it: name, amount, unit, aisle, in_pantry, checked. */
    private fun ShoppingMerge.ListRow.asFixture() =
        listOf(name, amount, unit, aisle, if (inPantry) "1" else "0", if (checked) "1" else "0")

    @Test
    fun addWeekMatchesThePythonApp() {
        for (case in ParityFixtures.cases("shopping_merge.json")) {
            val c = case.obj
            val meals = c["meals"]!!.jsonArray.map { m ->
                ShoppingMerge.PlannedMeal(
                    servings = m.obj["servings"]!!.str(),
                    yieldAmount = m.obj["yield"]!!.str(),
                    ingredients = m.obj["ingredients"]!!.jsonArray.map(::line),
                )
            }
            // Only on-hand items are passed in: choosing them is the caller's job
            // (Plan 3's repository), as the SQL WHERE "active" = 1 is in Python.
            val pantry = c["pantry"]!!.jsonArray
                .filter { it.jsonArray[2].jsonPrimitive.boolean }
                .map { PantryRule(it.jsonArray[0].str()!!, it.jsonArray[1].jsonPrimitive.boolean) }
            val knownAisles = c["known_aisles"]!!.obj.mapValues { it.value.str()!! }
            val existing = c["existing"]!!.jsonArray.mapIndexed { i, r ->
                ShoppingMerge.ListRow(
                    id = i + 1L,
                    name = r.obj["name"]!!.str()!!,
                    amount = r.obj["amount"]!!.str(),
                    unit = r.obj["unit"]!!.str(),
                    aisle = r.obj["aisle"]!!.str(),
                    inPantry = false,
                    checked = r.obj["checked"]!!.jsonPrimitive.int == 1,
                )
            }
            val expected = c["expected"]!!.jsonArray.map { r ->
                listOf(
                    r.obj["name"]!!.str(), r.obj["amount"]!!.str(), r.obj["unit"]!!.str(),
                    r.obj["aisle"]!!.str(), r.obj["in_pantry"]!!.str(), r.obj["checked"]!!.str(),
                )
            }

            val actual = ShoppingMerge.addWeek(existing, meals, pantry, knownAisles)

            assertEquals(c["name"]!!.str(), expected, actual.map { it.asFixture() })
        }
    }

    @Test
    fun addingAWeekNeverDropsARow() {
        val existing = listOf(ShoppingMerge.ListRow(1, "Paper towels", null, null, "Household", false, true))
        val meals = listOf(ShoppingMerge.PlannedMeal(null, null, listOf(Line("Flour", "1", "cup"))))

        val result = ShoppingMerge.addWeek(existing, meals, emptyList(), emptyMap())

        assertEquals(existing[0], result[0])
        assertEquals(listOf("Paper towels", "Flour"), result.map { it.name })
    }

    @Test
    fun mergeAmountsReturnsNullForIncompatibleUnits() {
        val row = ShoppingMerge.ListRow(1, "Sugar", "1", "lb", null, false, false)
        assertEquals(null, ShoppingMerge.mergeAmounts(row, "1", "cup"))
        assertEquals("3" to "lb", ShoppingMerge.mergeAmounts(row, "2", "lb"))
    }
}
