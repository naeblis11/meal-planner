package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.domain.YamlMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RecipeEditsTest {
    @Suppress("UNCHECKED_CAST")
    private fun doc(yaml: String) = RecipeYaml.load(yaml) as YamlMap

    private val cake = """
        recipe_name: Cake
        category: Desserts
        subcategory: None
        author: Ann
        oven_temp:
        - amount: 350
          unit: F
        oven_time: 30 min
        yields:
        - servings: 8
        notes:
        - Freezes well.
        - Better next day.
        ingredients:
        - Flour:
            usda_num: 20081
            amounts:
            - amount: 2
              unit: cup
            notes:
            - sifted
        - Butter:
            amounts:
            - amount: 1
              unit: cup
            - amount: 8
              unit: oz
            section: Frosting
        steps:
        - step: Mix.
          notes:
          - well
        - step: Bake.
    """.trimIndent()

    @Test
    fun formForPrefillsEveryField() {
        val form = RecipeEdits.formFor(doc(cake))
        assertEquals("Cake", form.name)
        assertEquals("Desserts", form.category)
        assertEquals("", form.subcategory)
        assertEquals("350", form.ovenTempAmount)
        assertEquals("F", form.ovenTempUnit)
        assertEquals("8", form.servingsAmount)
        assertEquals("servings", form.servingsUnit)
        assertEquals("Freezes well.\nBetter next day.", form.notes)
        assertEquals(listOf("e0", "s1", "e1"), form.rows.map { it.key })
        assertEquals(EditorRowState("e0", SubmittedRow.INGREDIENT, "Flour", "2", "cup", "sifted"), form.rows[0])
        assertEquals(SubmittedRow.SECTION, form.rows[1].kind)
        assertEquals(true, form.rows[2].locked)
        assertEquals(listOf(StepState("e0", "Mix."), StepState("e1", "Bake.")), form.steps)
    }

    @Test
    fun applyWritesTheFormBackLikeThePi() {
        val form = RecipeEdits.formFor(doc(cake)).copy(
            name = " Chocolate cake ", author = "", category = "", servingsAmount = "1 1/2", servingsUnit = "",
            ovenTempAmount = "180", ovenTempUnit = "c", notes = "  \nOne note\n",
            steps = listOf(StepState("e1", "Bake at 350."), StepState("e0", "Mix dry.")),
        )
        val result = RecipeEdits.apply(doc(cake), form, setOf("soup"))
        assertEquals("Chocolate cake", result["recipe_name"])
        assertFalse(result.containsKey("author"))
        assertEquals("None", result["category"])
        assertEquals(listOf(mapOf("amount" to "1 1/2", "unit" to "servings")), result["yields"])
        assertEquals(listOf(mapOf("amount" to 180L, "unit" to "C")), result["oven_temp"])
        assertEquals(listOf("One note"), result["notes"])
        @Suppress("UNCHECKED_CAST")
        val steps = result["steps"] as List<Map<Any?, Any?>>
        assertEquals(listOf("Bake at 350.", "Mix dry."), steps.map { it["step"] })
        assertEquals(listOf("well"), steps[1]["notes"])
        @Suppress("UNCHECKED_CAST")
        val flour = (result["ingredients"] as List<Map<Any?, Any?>>)[0]["Flour"] as Map<Any?, Any?>
        assertEquals(20081, flour["usda_num"])
    }

    @Test
    fun wholeServingsAreStoredAsNumbersAndBlankFieldsAreRemoved() {
        val form = RecipeEdits.formFor(doc(cake)).copy(servingsAmount = "6", ovenTempAmount = "", ovenTime = "", notes = "")
        val result = RecipeEdits.apply(doc(cake), form, emptySet())
        assertEquals(listOf(mapOf("amount" to 6L, "unit" to "servings")), result["yields"])
        assertFalse(result.containsKey("oven_temp"))
        assertFalse(result.containsKey("oven_time"))
        assertFalse(result.containsKey("notes"))
    }

    @Test
    fun refusesWhatThePiRefuses() {
        val form = RecipeEdits.formFor(doc(cake))
        fun message(f: RecipeForm, others: Set<String> = emptySet()) =
            assertThrows(RecipeEditException::class.java) { RecipeEdits.apply(doc(cake), f, others) }.message
        assertEquals("A recipe needs a title.", message(form.copy(name = "  ")))
        assertEquals("Another recipe is already called 'Soup'. Pick a different title.", message(form.copy(name = "Soup"), setOf("soup")))
        assertEquals("Enter a valid serving amount.", message(form.copy(servingsAmount = "lots")))
        assertEquals("Enter a valid serving amount.", message(form.copy(servingsAmount = "0")))
        assertEquals("A recipe needs at least one instruction step.", message(form.copy(steps = listOf(StepState("e0", " ")))))
        assertEquals("A recipe needs at least one ingredient.", message(form.copy(rows = emptyList())))
    }

    @Test
    fun aNewRecipeStartsEmpty() {
        val form = RecipeEdits.formFor(RecipeEdits.newRecipeDoc())
        assertEquals(RecipeForm(), form)
        val filled = form.copy(
            name = "Toast",
            rows = listOf(EditorRowState("n0", SubmittedRow.INGREDIENT, "Bread", "2", "slice")),
            steps = listOf(StepState("n1", "Toast it.")),
        )
        val result = RecipeEdits.apply(RecipeEdits.newRecipeDoc(), filled, emptySet())
        assertEquals("Toast", result["recipe_name"])
        assertEquals("None", result["category"])
    }
}
