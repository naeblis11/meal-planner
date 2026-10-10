package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AislesAndPantryParityTest {
    @Test
    fun categorize() {
        for (case in ParityFixtures.cases("aisles.json")) {
            val name = case.obj["name"]!!.str()!!
            assertEquals("categorize($name)", case.obj["expected"]!!.str(), GroceryCategories.categorize(name))
        }
    }

    @Test
    fun pantryMatch() {
        for (case in ParityFixtures.cases("pantry_match.json")) {
            val rule = PantryRule(case.obj["pantry"]!!.str()!!, case.obj["exact"]!!.jsonPrimitive.boolean)
            val ingredient = case.obj["ingredient"]!!.str()!!
            assertEquals("$rule covers $ingredient", case.obj["expected"]!!.jsonPrimitive.boolean,
                rule.covers(ingredient))
        }
    }

    @Test
    fun aisleOrderMatchesTheWebApp() {
        assertEquals(12, GroceryCategories.AISLE_ORDER.size)
        assertEquals("Produce", GroceryCategories.AISLE_ORDER.first())
        assertEquals("Household", GroceryCategories.AISLE_ORDER.last())
    }
}
