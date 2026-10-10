package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnSplitTest {
    @Test
    fun equalItemsSplitEvenlyInOrder() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), ColumnSplit.contiguous(listOf("a", "b", "c", "d"), 2) { 3 })
    }

    @Test
    fun aHeavyFirstItemTakesAColumnToItself() {
        assertEquals(listOf(listOf(5), listOf(1, 1, 1, 1, 1)), ColumnSplit.contiguous(listOf(5, 1, 1, 1, 1, 1), 2) { it })
    }

    @Test
    fun fewerItemsThanColumnsGivesOneColumnEach() {
        assertEquals(listOf(listOf("a"), listOf("b")), ColumnSplit.contiguous(listOf("a", "b"), 3) { 1 })
    }

    // Pins the greedy cut: shares of 5.33, 5.5 and 4 give runs of weight 5, 7 and 4. A tie (adding the 3 overshoots by
    // exactly as much as stopping falls short) takes the item, so the middle run is the heaviest.
    @Test
    fun threeColumnsOfUnequalWeightsAreCutGreedily() {
        assertEquals(
            listOf(listOf(4, 1), listOf(1, 3, 3), listOf(2, 2)),
            ColumnSplit.contiguous(listOf(4, 1, 1, 3, 3, 2, 2), 3) { it },
        )
    }

    @Test
    fun nothingGivesNoColumns() {
        assertEquals(emptyList<List<String>>(), ColumnSplit.contiguous(emptyList<String>(), 2) { 1 })
    }
}
