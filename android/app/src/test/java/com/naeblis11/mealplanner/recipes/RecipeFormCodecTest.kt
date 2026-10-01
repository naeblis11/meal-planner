package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.domain.SubmittedRow
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeFormCodecTest {
    @Test
    fun aFormRoundTripsThroughText() {
        val form = RecipeForm(
            name = "Soup \"deluxe\"", category = "Soups & Stews", subcategory = "", author = "Me", sourceUrl = "https://x",
            servingsAmount = "4", servingsUnit = "bowls", ovenTempAmount = "350", ovenTempUnit = "F", ovenTime = "1 hr",
            notes = "line 1\nline 2",
            rows = listOf(
                EditorRowState("e0", SubmittedRow.SECTION, "Base"),
                EditorRowState("n3", SubmittedRow.INGREDIENT, "Salt", "a pinch", "", "fine", locked = true, needsInput = true),
            ),
            steps = listOf(StepState("e0", "Stir."), StepState("n4", "")),
        )
        assertEquals(form, RecipeFormCodec.decode(RecipeFormCodec.encode(form)))
    }
}
