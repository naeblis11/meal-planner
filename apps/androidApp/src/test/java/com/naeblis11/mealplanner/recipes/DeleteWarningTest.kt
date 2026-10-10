package com.naeblis11.mealplanner.recipes

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteWarningTest {
    @Test
    fun saysWhatHappensToThePlannedMeals() {
        assertEquals("It is removed from this phone. This can't be undone.", deleteWarning(0))
        assertEquals(
            "It is on your meal plan once; deleting it removes that meal from the plan too. This can't be undone.",
            deleteWarning(1),
        )
        assertEquals(
            "It is on your meal plan 3 times; deleting it removes those meals from the plan too. This can't be undone.",
            deleteWarning(3),
        )
    }
}
