package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AisleGroupsTest {
    private data class Item(val name: String, val aisle: String?)

    @Test
    fun storeOrderThenOtherAislesThenUncategorized() {
        val items = listOf(
            Item("milk", "Dairy & Eggs"), Item("Zest", "Zebra aisle"), Item("bananas", "Produce"),
            Item("Apples", "Produce"), Item("tape", null), Item("glue", ""), Item("Cups", "Apples aisle"),
            Item("mystery", "Uncategorized"),
        )
        val groups = AisleGroups.group(items, { it.aisle }, { it.name })
        assertEquals(listOf("Produce", "Dairy & Eggs", "Apples aisle", "Zebra aisle", "Uncategorized"), groups.map { it.aisle })
        assertEquals(listOf("Apples", "bananas"), groups[0].items.map { it.name })
        assertEquals(listOf("glue", "mystery", "tape"), groups.last().items.map { it.name })
    }
}
